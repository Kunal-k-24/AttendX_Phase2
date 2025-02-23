package com.example.attendx;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import com.google.zxing.Result;
import me.dm7.barcodescanner.zxing.ZXingScannerView;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.text.SimpleDateFormat;
import java.util.Date;

public class QRScannerActivity extends AppCompatActivity implements ZXingScannerView.ResultHandler {
    private ZXingScannerView scannerView;
    private DatabaseReference usersRef, attendanceRef;
    private String userId, studentName, enrollmentNo;
    private boolean isStudentDataLoaded = false;
    private int failedAttempts = 0;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scannerView = new ZXingScannerView(this);
        setContentView(scannerView);

        userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        usersRef = FirebaseDatabase.getInstance().getReference("Users").child(userId);

        // ✅ Request Camera Permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 100);
        } else {
            startQRScanner();
        }

        fetchStudentData();
        setupBiometricAuth();
    }

    // ✅ Handle Camera Permission at Runtime
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startQRScanner();
            } else {
                Toast.makeText(this, "Camera permission is required to scan QR codes!", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void fetchStudentData() {
        usersRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    studentName = snapshot.child("name").getValue(String.class);
                    enrollmentNo = snapshot.child("enrollment").getValue(String.class);
                    isStudentDataLoaded = (studentName != null && enrollmentNo != null);
                } else {
                    Toast.makeText(QRScannerActivity.this, "Student data not found!", Toast.LENGTH_LONG).show();
                    redirectToStudentDashboard();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(QRScannerActivity.this, "Failed to fetch student data.", Toast.LENGTH_LONG).show();
                redirectToStudentDashboard();
            }
        });
    }

    private void setupBiometricAuth() {
        Executor executor = ContextCompat.getMainExecutor(this);
        biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                checkIfAlreadyMarked();
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                failedAttempts++;
                if (failedAttempts >= 4) {
                    Toast.makeText(QRScannerActivity.this, "Invalid fingerprint. ", Toast.LENGTH_LONG).show();
                    redirectToStudentDashboard();
                }
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Fingerprint Authentication")
                .setSubtitle("Use your fingerprint to mark attendance")
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();
    }

    private void startQRScanner() {
        scannerView.setResultHandler(this);
        scannerView.startCamera();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startQRScanner();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        scannerView.stopCamera();
    }

    @Override
    public void handleResult(Result rawResult) {
        if (!isStudentDataLoaded) {
            Toast.makeText(QRScannerActivity.this, "Student data not loaded. ", Toast.LENGTH_SHORT).show();
            redirectToStudentDashboard();
            return;
        }

        try {
            JSONObject qrData = new JSONObject(rawResult.getText());
            String date = qrData.getString("date");
            String subject = qrData.getString("subject");
            String lectureType = qrData.getString("lectureType");
            String sessionId = qrData.getString("sessionId");

            attendanceRef = FirebaseDatabase.getInstance().getReference("attendance")
                    .child(date)
                    .child(subject)
                    .child(lectureType)
                    .child(sessionId)
                    .child("students")
                    .child(userId);

            checkBiometricSupport();

        } catch (Exception e) {
            Toast.makeText(QRScannerActivity.this, "Invalid QR code.", Toast.LENGTH_SHORT).show();
            redirectToStudentDashboard();
        }
    }

    private void checkBiometricSupport() {
        BiometricManager biometricManager = BiometricManager.from(this);
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) {
            biometricPrompt.authenticate(promptInfo);
        } else {
            Toast.makeText(QRScannerActivity.this, "Your device does not support fingerprint authentication. Go for Voice Recognition ...", Toast.LENGTH_LONG).show();
            redirectToStudentDashboard();
        }
    }

    private void checkIfAlreadyMarked() {
        attendanceRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    Toast.makeText(QRScannerActivity.this, "Attendance already marked. ", Toast.LENGTH_SHORT).show();
                    redirectToStudentDashboard();
                } else {
                    markAttendance();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(QRScannerActivity.this, "Error checking attendance. ", Toast.LENGTH_SHORT).show();
                redirectToStudentDashboard();
            }
        });
    }

    private void markAttendance() {
        HashMap<String, Object> studentData = new HashMap<>();
        studentData.put("name", studentName);
        studentData.put("enrollment", enrollmentNo);
        studentData.put("timestamp", new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));

        attendanceRef.setValue(studentData)
                .addOnSuccessListener(unused -> {
                    Toast.makeText(QRScannerActivity.this, "Attendance Marked!", Toast.LENGTH_SHORT).show();
                    redirectToStudentDashboard();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(QRScannerActivity.this, "Failed to mark attendance. ", Toast.LENGTH_SHORT).show();
                    redirectToStudentDashboard();
                });
    }

    private void redirectToStudentDashboard() {
        Intent intent = new Intent(QRScannerActivity.this, StudentDashboardActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }
}
