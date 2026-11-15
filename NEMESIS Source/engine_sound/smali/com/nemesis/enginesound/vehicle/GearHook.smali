.class public Lcom/nemesis/enginesound/vehicle/GearHook;
.super Lcom/hkmc/micom/VehicleInfoListener;
.source "GearHook.smali"

# direct methods
.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Lcom/hkmc/micom/VehicleInfoListener;-><init>()V

    return-void
.end method

# virtual methods
.method public onGearStatus(I)V
    .registers 2

    invoke-static {p1}, Lcom/nemesis/enginesound/vehicle/GearSink;->onGear(I)V

    return-void
.end method
